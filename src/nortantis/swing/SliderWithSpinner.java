package nortantis.swing;

import javax.swing.*;
import java.util.Arrays;

public class SliderWithSpinner
{
	private final JSlider slider;
	private final JSpinner spinner;
	private boolean updatingFromSync;

	public SliderWithSpinner(JSlider slider, Runnable changeListener)
	{
		this.slider = slider;
		this.spinner = new JSpinner(new SpinnerNumberModel(slider.getValue(), slider.getMinimum(), slider.getMaximum(), 1));

		int columns = Math.max(Integer.toString(slider.getMinimum()).length(), Integer.toString(slider.getMaximum()).length());
		((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(columns);

		SwingHelper.addListener(slider, () ->
		{
			if (updatingFromSync)
				return;
			updatingFromSync = true;
			try
			{
				spinner.setValue(slider.getValue());
				changeListener.run();
			}
			finally
			{
				updatingFromSync = false;
			}
		}, true);
		SwingHelper.addListener(spinner, () ->
		{
			if (updatingFromSync)
				return;
			updatingFromSync = true;
			try
			{
				slider.setValue(((Number) spinner.getValue()).intValue());
				changeListener.run();
			}
			finally
			{
				updatingFromSync = false;
			}
		});
	}

	public void commitEdit()
	{
		try
		{
			spinner.commitEdit();
		}
		catch (java.text.ParseException ignored)
		{
		}
	}

	public RowHider addToOrganizer(GridBagOrganizer organizer, String label, String toolTip)
	{
		return organizer.addLabelAndComponentsHorizontal(label, toolTip, Arrays.asList(slider, spinner));
	}
}
